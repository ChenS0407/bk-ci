<template>
    <section>
        <draggable
            :class="{
                'container-atom-list': true,
                'trigger-container': stageIndex === 0,
                'readonly': !editable
            }"
            :data-baseos="container.baseOS || container.classType"
            v-model="atomList"
            v-bind="dragOptions"
            :move="checkMove"
        >
            <atom
                v-for="(atom, index) in atomList"
                :key="atom.id"
                :stage="stage"
                :container="container"
                :atom="atom"
                :stage-index="stageIndex"
                :container-index="containerIndex"
                :container-group-index="containerGroupIndex"
                :atom-index="index"
                :container-disabled="containerDisabled"
                :is-waiting="isWaiting"
                :cancel-user-id="cancelUserId"
                :user-name="userName"
                :editable="editable"
                :can-skip-element="canSkipElement"
                :is-last-atom="index === atomList.length - 1"
                :prev-atom="index > 0 ? atomList[index - 1] : null"
                :match-rules="matchRules"
                @[COPY_EVENT_NAME]="handleCopy"
                @[DELETE_EVENT_NAME]="handleDelete"
            />
            
            <span
                v-if="editable"
                :class="{ 'add-atom-entry': true, 'block-add-entry': atomList.length === 0 }"
                @click="editAtom(atomList.length - 1, true)"
            >
                <i class="add-plus-icon" />
                <template v-if="atomList.length === 0">
                    <span class="add-atom-label">{{ t('addAtom') }}</span>
                    <Logo class="atom-invalid-icon" name="exclamation-triangle-shape" />
                </template>
            </span>
        </draggable>
    </section>
</template>

<script>
    
    import draggable from 'vuedraggable'
    import Atom from './Atom'
    import Logo from './Logo'
    import { eventBus } from './util'
    import { localeMixins } from './locale'
    import {
        DELETE_EVENT_NAME,
        COPY_EVENT_NAME,
        ATOM_ADD_EVENT_NAME,
        STATUS_MAP
    } from './constants'
    export default {
        name: 'atom-list',
        components: {
            draggable,
            Logo,
            Atom
        },
        mixins: [localeMixins],
        props: {
            stage: {
                type: Object,
                required: true
            },
            container: {
                type: Object,
                required: true
            },
            stageIndex: {
                type: Number,
                required: true
            },
            containerIndex: {
                type: Number,
                required: true
            },
            containerGroupIndex: Number,
            containerStatus: String,
            containerDisabled: Boolean,
            editable: {
                type: Boolean,
                default: true
            },
            isPreview: {
                type: Boolean,
                default: false
            },
            canSkipElement: {
                type: Boolean,
                default: false
            },
            handleChange: {
                type: Function,
                required: true
            },
            cancelUserId: {
                type: String,
                default: 'unknow'
            },
            userName: {
                type: String,
                default: 'unknow'
            },
            matchRules: {
                type: Array,
                default: () => []
            }
        },
        data () {
            return {
                atomMap: {},
                DELETE_EVENT_NAME,
                COPY_EVENT_NAME
            }
        },
        computed: {
            isWaiting () {
                return this.containerStatus === STATUS_MAP.PREPARE_ENV
            },
            isInstanceEditable () {
                return !this.editable && this.pipeline && this.pipeline.instanceFromTemplate
            },
            atomList: {
                get () {
                    return this.container.elements.map(atom => {
                        atom.isReviewing = atom.status === STATUS_MAP.REVIEWING
                        if (atom.isReviewing) {
                            const atomReviewer = this.getReviewUser(atom)
                            atom.computedReviewers = atomReviewer
                        }
                        if (!atom.atomCode) {
                            atom.atomCode = atom['@type']
                        }
                        return atom
                    })
                },
                set (elements) {
                    this.handleChange(this.container, { elements })
                }
            },
            dragOptions () {
                return {
                    group: 'pipeline-atom',
                    ghostClass: 'sortable-ghost-atom',
                    chosenClass: 'sortable-chosen-atom',
                    animation: 130,
                    disabled: !this.editable
                }
            }
        },
        methods: {
            handleCopy ({ elementIndex, element }) {
                this.container.elements.splice(elementIndex + 1, 0, element)
            },
            handleDelete ({ elementIndex }) {
                this.container.elements.splice(elementIndex, 1)
            },
            checkMove (event) {
                const dragContext = event.draggedContext || {}
                const element = dragContext.element || {}
                const atomCode = element.atomCode || ''
                const os = element.os || []
                const isTriggerAtom = element.category === 'TRIGGER'

                const to = event.to || {}
                const dataSet = to.dataset || {}
                const baseOS = dataSet.baseos || ''
                const isJobTypeOk = os.includes(baseOS) || (os.length <= 0 && (!baseOS || baseOS === 'normal'))
                return !!atomCode && (
                    (isTriggerAtom && baseOS === 'trigger')
                    || (!isTriggerAtom && isJobTypeOk)
                    || (!isTriggerAtom && baseOS !== 'trigger' && os.length <= 0 && element.buildLessRunFlag)
                )
            },

            getReviewUser (atom) {
                try {
                    const list = atom.reviewUsers || (atom.data && atom.data.input && atom.data.input.reviewers)
                    const reviewUsers = list.map(user => user.split(';').map(val => val.trim())).reduce((prev, curr) => {
                        return prev.concat(curr)
                    }, [])
                    return reviewUsers
                } catch (error) {
                    console.error(error)
                    return []
                }
            },
            editAtom (atomIndex, isAdd) {
                const { stageIndex, containerIndex, container } = this
                const editAction = isAdd ? ATOM_ADD_EVENT_NAME : DELETE_EVENT_NAME
                eventBus.$emit(editAction, {
                    container,
                    atomIndex,
                    stageIndex,
                    containerIndex
                })
            }
            
        }
    }
</script>

<style lang="scss">
    @import "./conf";
    .container-atom-list {
        position: relative;
        z-index: 3;

        .sortable-ghost-atom {
            opacity: 0.5;
        }
        .sortable-chosen-atom {
            transform: scale(1.0);
        }
        .add-atom-entry {
            position: absolute;
            bottom: -10px;
            left: 111px;
            background-color: white;
            cursor: pointer;
            z-index: 3;
            .add-plus-icon {
                @include add-plus-icon($fontLighterColor, $fontLighterColor, white, 18px, true);
                @include add-plus-icon-hover($primaryColor, $primaryColor, white);
            }
            &.block-add-entry {
                display: flex;
                flex-direction: row;
                align-items: center;
                height: $itemHeight;
                margin: 0 0 11px 0;
                background-color: #fff;
                border-radius: 2px;
                font-size: 14px;
                transition: all .4s ease-in-out;
                z-index: 2;
                position: static;
                padding-right: 12px;
                border-style: dashed;
                color: $dangerColor;
                border-color: $dangerColor;
                border-width: 1px;
                .add-atom-label {
                    flex: 1;
                    color: $borderWeightColor;
                }
                .add-plus-icon {
                    margin: 12px 13px;
                }
                &:before,
                &:after {
                    display: none;
                }
            }

            &:hover {
                border-color: $primaryColor;
                color: $primaryColor;
            }
        }
    }
</style>
